from sqlalchemy import Column, Integer, String, Text, DateTime, ForeignKey, UniqueConstraint
from sqlalchemy.orm import relationship
import uuid
import datetime
from server.database import Base

class User(Base):
    __tablename__ = "users"
    id = Column(String, primary_key=True, default=lambda: str(uuid.uuid4()))
    email = Column(String, unique=True, index=True)
    hashed_password = Column(String)

class Contact(Base):
    __tablename__ = "contacts"
    id = Column(String, primary_key=True, default=lambda: str(uuid.uuid4()))
    name = Column(String, index=True)
    phone_number = Column(String, unique=True, index=True)
    organization = Column(String, nullable=True)
    created_at = Column(DateTime, default=datetime.datetime.utcnow)

    calls = relationship("Call", back_populates="contact")
    messages = relationship("Message", back_populates="contact")
    ai_summaries = relationship("AISummary", back_populates="contact")
    recordings = relationship("Recording", back_populates="contact")

class Call(Base):
    __tablename__ = "calls"
    __table_args__ = (UniqueConstraint('contact_id', 'timestamp', name='uix_call_contact_timestamp'),)
    id = Column(String, primary_key=True, default=lambda: str(uuid.uuid4()))
    contact_id = Column(String, ForeignKey("contacts.id"))
    direction = Column(String, default="OUT")  # IN / OUT
    duration = Column(Integer)  # in seconds
    timestamp = Column(DateTime, default=datetime.datetime.utcnow)

    contact = relationship("Contact", back_populates="calls")

class Message(Base):
    __tablename__ = "messages"
    __table_args__ = (UniqueConstraint('contact_id', 'timestamp', 'content', name='uix_msg_contact_timestamp_content'),)
    id = Column(String, primary_key=True, default=lambda: str(uuid.uuid4()))
    contact_id = Column(String, ForeignKey("contacts.id"))
    content = Column(Text)
    direction = Column(String)  # SENT / INBOX
    timestamp = Column(DateTime, default=datetime.datetime.utcnow)

    contact = relationship("Contact", back_populates="messages")

class AISummary(Base):
    __tablename__ = "ai_summaries"
    id = Column(String, primary_key=True, default=lambda: str(uuid.uuid4()))
    contact_id = Column(String, ForeignKey("contacts.id"))
    summary = Column(Text)
    status = Column(String)  # HOT, WARM, COLD
    next_action = Column(Text)
    updated_at = Column(DateTime, default=datetime.datetime.utcnow, onupdate=datetime.datetime.utcnow)

    contact = relationship("Contact", back_populates="ai_summaries")

class Recording(Base):
    __tablename__ = "recordings"
    id = Column(String, primary_key=True, default=lambda: str(uuid.uuid4()))
    contact_id = Column(String, ForeignKey("contacts.id"))
    file_path = Column(String)
    summary = Column(Text, nullable=True)
    transcription = Column(Text, nullable=True)
    timestamp = Column(DateTime, default=datetime.datetime.utcnow)

    contact = relationship("Contact", back_populates="recordings")
